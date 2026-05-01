package com.aritr.rova.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 2 Slice 2.1a — Compose surface for a single recovery card.
 *
 * Pure-presentation: takes a [RecoveryCardState] (built by
 * [RecoveryUiStateMapper]) plus pass-in callbacks. No `RovaApp`
 * reference, no `SessionStore` coupling, no real Merge/Discard
 * behavior — wiring is the consumer's responsibility (Slice 2.1b
 * once the `MainScreen.kt` worktree drift is reconciled).
 *
 * `vendorHelpSlot` is rendered only when the underlying state has
 * `showVendorHelpSlot == true` (currently only
 * [RecoveryCardKind.KILLED_BY_SYSTEM]) AND the consumer supplies a
 * non-null slot. Slice 2.2 will fill the slot with the vendor-intent
 * helper; until then, `null` is the correct default and the card
 * silently omits the slot.
 */
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
    onMerge: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlot: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.body,
                style = MaterialTheme.typography.bodyMedium
            )

            if (state.survivingArtifacts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column {
                    state.survivingArtifacts.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (state.showVendorHelpSlot && vendorHelpSlot != null) {
                Spacer(Modifier.height(12.dp))
                vendorHelpSlot()
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDiscard) {
                    Text(state.discardLabel)
                }
                Spacer(Modifier.height(0.dp))
                OutlinedButton(
                    onClick = onMerge,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(state.mergeLabel)
                }
            }
        }
    }
}

/**
 * Renders every card in [state] as a vertical column. Empty
 * [RecoveryUiState.cards] renders nothing — the consumer can place
 * this composable unconditionally.
 *
 * `onMerge` / `onDiscard` receive the `sessionId` so the wiring
 * layer can route to the per-session merge or discard path.
 *
 * `vendorHelpSlotFor` is invoked per card with the card's
 * `sessionId`; consumers that don't have a vendor helper yet can
 * pass `{ _ -> null }` (or omit; the default is `null`).
 */
@Composable
fun RecoveryCardList(
    state: RecoveryUiState,
    onMerge: (sessionId: String) -> Unit,
    onDiscard: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlotFor: (sessionId: String) -> (@Composable () -> Unit)? = { null }
) {
    if (state.cards.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        state.cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            RecoveryCard(
                state = card,
                onMerge = { onMerge(card.sessionId) },
                onDiscard = { onDiscard(card.sessionId) },
                vendorHelpSlot = vendorHelpSlotFor(card.sessionId)
            )
        }
    }
}
