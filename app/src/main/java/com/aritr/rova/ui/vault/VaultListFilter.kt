package com.aritr.rova.ui.vault

import com.aritr.rova.data.VaultState

/** B5 / ADR-0025 — normal Library shows only PUBLIC. */
fun isLibraryVisible(state: VaultState): Boolean = state == VaultState.PUBLIC

/**
 * B5 / ADR-0025 (spec O1) — the vault shows VAULTED and UNVAULTING: an
 * interrupted move-out stays vault-visible (hidden) until publish confirms
 * PUBLIC. VAULTING is shown in neither stable list.
 */
fun isVaultVisible(state: VaultState): Boolean =
    state == VaultState.VAULTED || state == VaultState.UNVAULTING
