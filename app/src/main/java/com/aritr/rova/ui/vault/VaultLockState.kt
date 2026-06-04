package com.aritr.rova.ui.vault

/**
 * B5 / ADR-0025 — in-memory vault lock state. NEVER persisted (spec R5):
 * re-auth on every vault entry and on app foreground. `onAppBackgrounded`
 * is fired from a ProcessLifecycleOwner ON_STOP observer; `onLeaveVault`
 * when the vault route is popped.
 */
data class VaultLockState(val unlocked: Boolean) {
    fun onAuthSucceeded() = copy(unlocked = true)
    fun onAppBackgrounded() = copy(unlocked = false)
    fun onLeaveVault() = copy(unlocked = false)
    companion object { fun initial() = VaultLockState(unlocked = false) }
}
