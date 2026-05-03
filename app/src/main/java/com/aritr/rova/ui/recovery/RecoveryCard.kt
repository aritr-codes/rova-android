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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 2 Slice 2.1a — Compose surface for a single recovery card.
 *
 * Pure-presentation: takes a [RecoveryCardState] (built by
 * [RecoveryUiStateMapper]) plus a Discard callback. No `RovaApp`
 * reference, no `SessionStore` coupling.
 *
 * Internal beta correction (smoke 2026-05-03): the Merge button is
 * removed for beta — no service-side merge API exists yet and the
 * placeholder snackbar made the UI feel incomplete. Discard is wired
 * for real via the wiring layer. The card now exposes only the
 * Discard affordance.
 *
 * `vendorHelpSlot` is rendered only when the underlying state has
 * `showVendorHelpSlot == true` (currently only
 * [RecoveryCardKind.KILLED_BY_SYSTEM]) AND the consumer supplies a
 * non-null slot.
 */
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
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
                OutlinedButton(onClick = onDiscard) {
                    Text(state.discardLabel)
                }
            }
        }
    }
}

/**
 * Renders the (at most one) recovery card from [state] plus a small
 * footer line when [RecoveryUiState.hiddenCount] > 0 so the user
 * sees that older interrupted sessions exist without a stacking red
 * wall. Empty [RecoveryUiState.cards] renders nothing — the consumer
 * can place this composable unconditionally.
 *
 * `onDiscard` receives the visible card's `sessionId` so the wiring
 * layer can route to `RecoveryViewModel.dismiss(sessionId)`.
 *
 * `vendorHelpSlotFor` is invoked with the visible card's `sessionId`;
 * consumers without a vendor helper pass `{ _ -> null }` (or omit;
 * the default is `null`).
 */
@Composable
fun RecoveryCardList(
    state: RecoveryUiState,
    onDiscard: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlotFor: (sessionId: String) -> (@Composable () -> Unit)? = { null }
) {
    if (state.cards.isEmpty() && state.hiddenCount == 0) return
    Column(modifier = modifier.fillMaxWidth()) {
        state.cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            RecoveryCard(
                state = card,
                onDiscard = { onDiscard(card.sessionId) },
                vendorHelpSlot = vendorHelpSlotFor(card.sessionId)
            )
        }
        if (state.hiddenCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "+${state.hiddenCount} older interrupted sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
