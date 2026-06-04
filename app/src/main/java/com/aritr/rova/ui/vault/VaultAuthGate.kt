package com.aritr.rova.ui.vault

import android.app.KeyguardManager
import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.aritr.rova.R

/**
 * B5 / ADR-0025 — the single non-testable auth seam. ALL framework calls
 * (BiometricManager / BiometricPrompt / KeyguardManager) live here; the pure
 * path-selection logic is delegated to [vaultAuthPath] so it stays JVM-tested.
 *
 * Per project policy (JVM unit tests only, no Robolectric) this file is
 * compile-only — its correctness is covered by [VaultAuthDecisionTest] via the
 * pure helper plus on-device smoke testing.
 *
 * Auth paths (see [VaultAuthPath]):
 *  - BIOMETRIC_PROMPT (API 28+): BiometricPrompt with allowed authenticators
 *    BIOMETRIC_STRONG or DEVICE_CREDENTIAL. NOTE: setNegativeButtonText must
 *    NOT be combined with DEVICE_CREDENTIAL — that pairing throws at build().
 *  - KEYGUARD_INTENT (API 24–27): BiometricPrompt's DEVICE_CREDENTIAL combo is
 *    unreliable below P, so we hand the caller a confirm-device-credential
 *    Intent via [launchKeyguard]; the actual registerForActivityResult wiring
 *    lands in the UI slice.
 *  - UNAVAILABLE_NO_CREDENTIAL: no screen lock enrolled → [onUnavailable].
 */
object VaultAuthGate {

    /**
     * Prompts the user to authenticate before revealing the vault.
     *
     * @param activity host FragmentActivity (BiometricPrompt requirement).
     * @param onSucceeded auth confirmed — caller flips VaultLockState.unlocked.
     * @param onCancelled user dismissed / auth failed / hardware error.
     * @param onUnavailable no enrolled device credential — caller should prompt
     *   the user to set a screen lock.
     * @param launchKeyguard on API 24–27, invoked with the
     *   createConfirmDeviceCredentialIntent so the caller can launch it via an
     *   ActivityResult contract (wiring deferred to the UI slice).
     */
    fun authenticate(
        activity: FragmentActivity,
        onSucceeded: () -> Unit,
        onCancelled: () -> Unit,
        onUnavailable: () -> Unit,
        launchKeyguard: (Intent) -> Unit,
    ) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val hasEnrolledCredential = computeHasEnrolledCredential(activity, keyguard)

        when (vaultAuthPath(android.os.Build.VERSION.SDK_INT, hasEnrolledCredential)) {
            VaultAuthPath.BIOMETRIC_PROMPT ->
                showBiometricPrompt(activity, onSucceeded, onCancelled)

            VaultAuthPath.KEYGUARD_INTENT -> {
                val intent = keyguard?.createConfirmDeviceCredentialIntent(
                    activity.getString(R.string.vault_unlock_prompt_title),
                    activity.getString(R.string.vault_unlock_prompt_subtitle),
                )
                if (intent != null) launchKeyguard(intent) else onUnavailable()
            }

            VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL -> onUnavailable()
        }
    }

    /**
     * Enrolled-credential signal. BiometricManager.canAuthenticate with the
     * STRONG-or-DEVICE_CREDENTIAL combo is the primary check; on API 24–27 that
     * combo can be unreliable, so we also accept KeyguardManager.isDeviceSecure
     * as a fallback positive signal for a set screen lock.
     */
    private fun computeHasEnrolledCredential(
        activity: FragmentActivity,
        keyguard: KeyguardManager?,
    ): Boolean {
        val canAuth = BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) return true
        // Below API 28 the combined query is unreliable; fall back to keyguard.
        return keyguard?.isDeviceSecure == true
    }

    private fun showBiometricPrompt(
        activity: FragmentActivity,
        onSucceeded: () -> Unit,
        onCancelled: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onSucceeded()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    onCancelled()
                }

                override fun onAuthenticationFailed() {
                    // A single non-match is not terminal; the prompt stays up.
                    // Terminal cancel/error is delivered via onAuthenticationError.
                }
            },
        )

        // setNegativeButtonText is intentionally omitted: it is mutually
        // exclusive with DEVICE_CREDENTIAL and throws at build() if both are set.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_unlock_prompt_title))
            .setSubtitle(activity.getString(R.string.vault_unlock_prompt_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        prompt.authenticate(info)
    }
}
